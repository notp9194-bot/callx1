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
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.*;

/**
 * ChannelAdminActivity — full WhatsApp-level admin management panel (v5).
 *
 * v5 additions:
 *   ✓ NEW: Admin permission levels — per-admin checkboxes: Can Post / Can Edit / Can Manage
 *   ✓ NEW: Quick-link to ChannelAutoReplyActivity (welcome message settings)
 *   ✓ NEW: Quick-link to ChannelTopicsActivity (manage topic tags)
 *   ✓ FIXED: resolveAdminProfiles now uses ChannelViewModel.loadAdmins() via LiveData
 *   ✓ Owner can transfer ownership to any admin
 *   ✓ Add admin from search (UID or username)
 *   ✓ Remove admin
 *   ✓ Quick links: Followers / Invite Link / Scheduled Posts
 */
public class ChannelAdminActivity extends AppCompatActivity {

    public static final String EXTRA_CHANNEL_ID   = "channelId";
    public static final String EXTRA_CHANNEL_NAME = "channelName";
    public static final String EXTRA_OWNER_UID    = "ownerUid";

    private ChannelViewModel viewModel;
    private String channelId, channelName, ownerUid;
    private String myUid;
    private boolean isOwner = false;

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

        myUid    = FirebaseUtils.getMyUid();
        isOwner  = myUid != null && myUid.equals(ownerUid);

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

        // ── Quick-access buttons ──────────────────────────────────────────
        View btnFollowers   = findViewById(R.id.btn_view_followers);
        View btnInviteLink  = findViewById(R.id.btn_manage_invite_link);
        View btnScheduled   = findViewById(R.id.btn_scheduled_posts);
        View btnAutoReply   = findViewById(R.id.btn_auto_reply_settings);   // NEW
        View btnTopics      = findViewById(R.id.btn_manage_topics);          // NEW

        if (btnFollowers != null) btnFollowers.setOnClickListener(v -> {
            Intent i = new Intent(this, ChannelFollowersActivity.class);
            i.putExtra(ChannelFollowersActivity.EXTRA_CHANNEL_ID,   channelId);
            i.putExtra(ChannelFollowersActivity.EXTRA_CHANNEL_NAME, channelName);
            i.putExtra(ChannelFollowersActivity.EXTRA_OWNER_UID,    ownerUid);
            i.putExtra(ChannelFollowersActivity.EXTRA_IS_ADMIN,     true);
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

        if (btnAutoReply != null) btnAutoReply.setOnClickListener(v -> {
            Intent i = new Intent(this, ChannelAutoReplyActivity.class);
            i.putExtra(ChannelAutoReplyActivity.EXTRA_CHANNEL_ID,   channelId);
            i.putExtra(ChannelAutoReplyActivity.EXTRA_CHANNEL_NAME, channelName);
            startActivity(i);
        });

        if (btnTopics != null) btnTopics.setOnClickListener(v -> {
            Intent i = new Intent(this, ChannelTopicsActivity.class);
            i.putExtra(ChannelTopicsActivity.EXTRA_CHANNEL_ID, channelId);
            startActivity(i);
        });

        // ── Observe admins via ViewModel (FIXED: uses LiveData, not direct Firebase) ──
        viewModel.admins.observe(this, adminMap -> {
            if (adminMap == null) return;
            admins.clear();
            for (Map.Entry<String, String> e : adminMap.entrySet()) {
                AdminEntry ae = new AdminEntry();
                ae.uid  = e.getKey();
                ae.role = e.getValue();
                admins.add(ae);
            }
            resolveAdminProfiles();
        });

        viewModel.toastMessage.observe(this, msg -> {
            if (msg != null && !msg.isEmpty()) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });

        viewModel.loadAdmins(channelId);
    }

    // ── Resolve admin display profiles from Firebase users node ──────────

    private void resolveAdminProfiles() {
        if (admins.isEmpty()) { adapter.setData(admins, isOwner, ownerUid); return; }
        final int[] remaining = {admins.size()};
        for (AdminEntry ae : admins) {
            if (ae.uid == null) { done(remaining); continue; }
            FirebaseUtils.db().getReference("users").child(ae.uid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        Object nameObj = snap.child("displayName").getValue();
                        Object iconObj = snap.child("photoUrl").getValue();
                        ae.name    = nameObj != null ? nameObj.toString() : ae.uid;
                        ae.iconUrl = iconObj != null ? iconObj.toString() : null;
                        // Load admin permissions
                        FirebaseUtils.db().getReference("channelAdminPerms")
                            .child(channelId).child(ae.uid)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot ps) {
                                    ae.canPost   = !ps.hasChild("canPost")   || Boolean.TRUE.equals(ps.child("canPost").getValue(Boolean.class));
                                    ae.canEdit   = !ps.hasChild("canEdit")   || Boolean.TRUE.equals(ps.child("canEdit").getValue(Boolean.class));
                                    ae.canManage = !ps.hasChild("canManage") || Boolean.TRUE.equals(ps.child("canManage").getValue(Boolean.class));
                                    done(remaining);
                                }
                                @Override public void onCancelled(@NonNull DatabaseError e) { done(remaining); }
                            });
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { done(remaining); }
                });
        }
    }

    private void done(int[] counter) {
        counter[0]--;
        if (counter[0] <= 0) adapter.setData(admins, isOwner, ownerUid);
    }

    // ── Add admin dialog ──────────────────────────────────────────────────

    private void showAddAdminDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_add_admin, null);
        TextInputEditText etUid = v != null ? v.findViewById(R.id.et_admin_uid) : null;
        new AlertDialog.Builder(this)
            .setTitle("Add admin")
            .setView(v)
            .setPositiveButton("Add", (d, w) -> {
                String uid = etUid != null && etUid.getText() != null
                    ? etUid.getText().toString().trim() : "";
                if (!uid.isEmpty()) viewModel.addAdmin(channelId, uid, "admin");
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── AdminAdapter ──────────────────────────────────────────────────────

    class AdminAdapter extends RecyclerView.Adapter<AdminAdapter.VH> {
        private List<AdminEntry> data = new ArrayList<>();
        private boolean isOwner2 = false;
        private String  ownerUid2 = "";

        void setData(List<AdminEntry> d, boolean owner, String oUid) {
            data = d != null ? new ArrayList<>(d) : new ArrayList<>();
            isOwner2  = owner;
            ownerUid2 = oUid != null ? oUid : "";
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_channel_admin, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            AdminEntry ae = data.get(pos);
            if (h.tvName != null) h.tvName.setText(ae.name != null ? ae.name : ae.uid);
            if (h.tvRole != null) {
                boolean isThisOwner = ae.uid != null && ae.uid.equals(ownerUid2);
                h.tvRole.setText(isThisOwner ? "Owner" : "Admin");
            }
            if (h.ivIcon != null && ae.iconUrl != null && !ae.iconUrl.isEmpty())
                Glide.with(h.ivIcon.getContext()).load(ae.iconUrl).circleCrop().into(h.ivIcon);

            // Permission checkboxes (only visible to owner, not for own entry or owner entry)
            boolean canEditPerms = isOwner2 && !ae.uid.equals(myUid) && !ae.uid.equals(ownerUid2);
            if (h.cbCanPost   != null) { h.cbCanPost.setChecked(ae.canPost);   h.cbCanPost.setEnabled(canEditPerms); }
            if (h.cbCanEdit   != null) { h.cbCanEdit.setChecked(ae.canEdit);   h.cbCanEdit.setEnabled(canEditPerms); }
            if (h.cbCanManage != null) { h.cbCanManage.setChecked(ae.canManage); h.cbCanManage.setEnabled(canEditPerms); }

            if (h.cbCanPost != null && canEditPerms) {
                h.cbCanPost.setOnCheckedChangeListener((btn, c) -> {
                    ae.canPost = c;
                    viewModel.setAdminPermissions(channelId, ae.uid, ae.canPost, ae.canEdit, ae.canManage);
                });
            }
            if (h.cbCanEdit != null && canEditPerms) {
                h.cbCanEdit.setOnCheckedChangeListener((btn, c) -> {
                    ae.canEdit = c;
                    viewModel.setAdminPermissions(channelId, ae.uid, ae.canPost, ae.canEdit, ae.canManage);
                });
            }
            if (h.cbCanManage != null && canEditPerms) {
                h.cbCanManage.setOnCheckedChangeListener((btn, c) -> {
                    ae.canManage = c;
                    viewModel.setAdminPermissions(channelId, ae.uid, ae.canPost, ae.canEdit, ae.canManage);
                });
            }

            // Long-press → remove / transfer options
            h.itemView.setOnLongClickListener(v -> {
                if (!isOwner2 || ae.uid == null || ae.uid.equals(ownerUid2)) return false;
                String[] opts = {"Remove admin", "Transfer ownership"};
                new AlertDialog.Builder(ChannelAdminActivity.this)
                    .setTitle(ae.name != null ? ae.name : "Admin")
                    .setItems(opts, (d, w) -> {
                        if (w == 0) viewModel.removeAdmin(channelId, ae.uid);
                        else confirmTransfer(ae);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
                return true;
            });
        }

        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivIcon;
            TextView tvName, tvRole;
            MaterialCheckBox cbCanPost, cbCanEdit, cbCanManage;
            VH(View v) {
                super(v);
                ivIcon     = v.findViewById(R.id.iv_admin_icon);
                tvName     = v.findViewById(R.id.tv_admin_name);
                tvRole     = v.findViewById(R.id.tv_admin_role);
                cbCanPost   = v.findViewById(R.id.cb_can_post);
                cbCanEdit   = v.findViewById(R.id.cb_can_edit);
                cbCanManage = v.findViewById(R.id.cb_can_manage);
            }
        }
    }

    // ── Transfer ownership ────────────────────────────────────────────────

    private void confirmTransfer(AdminEntry ae) {
        new AlertDialog.Builder(this)
            .setTitle("Transfer ownership?")
            .setMessage("Transfer ownership to " + (ae.name != null ? ae.name : ae.uid)
                + "? You will become a regular admin.")
            .setPositiveButton("Transfer", (d, w) -> viewModel.transferOwnership(channelId, ae.uid))
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── Data class ────────────────────────────────────────────────────────

    static class AdminEntry {
        String uid, name, iconUrl, role;
        boolean canPost   = true;
        boolean canEdit   = true;
        boolean canManage = false;
    }
}
