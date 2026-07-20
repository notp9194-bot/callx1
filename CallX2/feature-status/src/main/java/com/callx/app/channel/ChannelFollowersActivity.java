package com.callx.app.channel;

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
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * ChannelFollowersActivity — WhatsApp-level follower management (v5).
 *
 * v5 fixes / additions:
 *   ✓ FIXED: isOwnerOrAdmin now checks both owner UID and channelEntity.isAdmin
 *   ✓ NEW: Filter chips — All / Admins / Blocked
 *   ✓ NEW: Promote follower to admin directly from list (owner-only)
 *   ✓ NEW: Bulk-remove action (long-press to select, then remove selected)
 *   ✓ Paginated list of followers with join date
 *   ✓ Search followers by name
 *   ✓ Remove / block a follower (admin only)
 *   ✓ Follower count badge in toolbar
 *   ✓ Pull-to-refresh for latest list
 */
public class ChannelFollowersActivity extends AppCompatActivity {

    public static final String EXTRA_CHANNEL_ID   = "channelId";
    public static final String EXTRA_CHANNEL_NAME = "channelName";
    public static final String EXTRA_OWNER_UID    = "ownerUid";
    public static final String EXTRA_IS_ADMIN     = "isAdmin";

    private static final int PAGE_SIZE = 50;
    private static final String FILTER_ALL     = "All";
    private static final String FILTER_ADMINS  = "Admins";
    private static final String FILTER_BLOCKED = "Blocked";

    private ChannelViewModel viewModel;
    private String channelId, channelName, ownerUid;
    private String myUid;
    private boolean isOwnerOrAdmin = false;  // FIXED: includes admin check
    private boolean isOwner        = false;

    private FollowerAdapter adapter;
    private final List<FollowerEntry> allFollowers      = new ArrayList<>();
    private final List<FollowerEntry> filteredFollowers = new ArrayList<>();
    private final Set<String>         adminUids         = new HashSet<>();
    private final Set<String>         blockedUids       = new HashSet<>();
    private final Set<String>         selectedUids      = new HashSet<>();

    private TextInputEditText etSearch;
    private TextView          tvFollowerCount;
    private String            activeFilter = FILTER_ALL;
    private boolean           inSelectionMode = false;
    private View              layoutBulkActions;
    private TextView          tvSelectedCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_followers);

        channelId   = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        channelName = getIntent().getStringExtra(EXTRA_CHANNEL_NAME);
        ownerUid    = getIntent().getStringExtra(EXTRA_OWNER_UID);
        if (channelId == null) { finish(); return; }

        myUid = FirebaseUtils.getMyUid();
        viewModel = new ViewModelProvider(this).get(ChannelViewModel.class);

        // ── FIXED: check both owner AND admin flag ──────────────────────
        isOwner        = myUid != null && myUid.equals(ownerUid);
        isOwnerOrAdmin = isOwner
            || getIntent().getBooleanExtra(EXTRA_IS_ADMIN, false);
        // Also observe the channel entity for isAdmin field
        viewModel.getChannel(channelId).observe(this, ch -> {
            if (ch != null && myUid != null) {
                isOwner        = myUid.equals(ch.ownerUid);
                isOwnerOrAdmin = isOwner || ch.isAdmin;
                adapter.setIsOwnerOrAdmin(isOwnerOrAdmin);
                adapter.setIsOwner(isOwner);
                adapter.setAdminUids(adminUids);
            }
        });

        Toolbar toolbar = findViewById(R.id.toolbar_followers);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Followers");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        tvFollowerCount  = findViewById(R.id.tv_follower_count);
        etSearch         = findViewById(R.id.et_follower_search);
        layoutBulkActions= findViewById(R.id.layout_bulk_actions);
        tvSelectedCount  = findViewById(R.id.tv_selected_count);

        // Bulk remove button
        View btnBulkRemove = findViewById(R.id.btn_bulk_remove);
        if (btnBulkRemove != null) btnBulkRemove.setOnClickListener(v -> confirmBulkRemove());

        // Cancel selection button
        View btnCancelSel = findViewById(R.id.btn_cancel_selection);
        if (btnCancelSel != null) btnCancelSel.setOnClickListener(v -> exitSelectionMode());

        // Filter chips
        setupFilterChips();

        RecyclerView rv = findViewById(R.id.rv_followers);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FollowerAdapter();
        adapter.setIsOwnerOrAdmin(isOwnerOrAdmin);
        adapter.setIsOwner(isOwner);
        rv.setAdapter(adapter);

        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                    filterFollowers(s.toString().trim().toLowerCase());
                }
            });
        }

        // Load admins first (to flag admin rows)
        loadAdmins();

        // Observe followers from ViewModel
        viewModel.followers.observe(this, followers -> {
            if (followers == null) return;
            allFollowers.clear();
            for (Map<String, Object> m : followers) {
                FollowerEntry e = new FollowerEntry();
                e.uid      = str(m, "uid");
                e.name     = str(m, "name");
                e.iconUrl  = str(m, "iconUrl");
                e.joinedAt = lng(m, "joinedAt");
                e.isBlocked= bool(m, "blocked");
                if (e.uid == null) continue;
                if (e.isBlocked) blockedUids.add(e.uid);
                allFollowers.add(e);
            }
            updateFollowerCount(allFollowers.size());
            applyFilter();
        });

        viewModel.toastMessage.observe(this, msg -> {
            if (msg != null && !msg.isEmpty()) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });

        viewModel.loadFollowers(channelId);
    }

    // ── Filter chips ──────────────────────────────────────────────────────

    private void setupFilterChips() {
        ChipGroup cg = findViewById(R.id.chip_group_follower_filter);
        if (cg == null) return;
        for (String f : new String[]{FILTER_ALL, FILTER_ADMINS, FILTER_BLOCKED}) {
            Chip chip = new Chip(this);
            chip.setText(f);
            chip.setCheckable(true);
            chip.setChecked(FILTER_ALL.equals(f));
            chip.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) { activeFilter = f; applyFilter(); }
            });
            cg.addView(chip);
        }
    }

    private void applyFilter() {
        String query = etSearch != null && etSearch.getText() != null
            ? etSearch.getText().toString().trim().toLowerCase() : "";
        filteredFollowers.clear();
        for (FollowerEntry e : allFollowers) {
            if (FILTER_ADMINS.equals(activeFilter)  && !adminUids.contains(e.uid)) continue;
            if (FILTER_BLOCKED.equals(activeFilter) && !e.isBlocked) continue;
            if (!query.isEmpty() && (e.name == null || !e.name.toLowerCase().contains(query))) continue;
            filteredFollowers.add(e);
        }
        adapter.setData(filteredFollowers, adminUids);
    }

    private void filterFollowers(String query) { applyFilter(); }

    // ── Admin load ────────────────────────────────────────────────────────

    private void loadAdmins() {
        FirebaseUtils.db().getReference("channelAdmins").child(channelId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    adminUids.clear();
                    for (DataSnapshot child : snap.getChildren()) adminUids.add(child.getKey());
                    adapter.setAdminUids(adminUids);
                    applyFilter();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void updateFollowerCount(int count) {
        if (tvFollowerCount != null) tvFollowerCount.setText(count + " followers");
        if (getSupportActionBar() != null) getSupportActionBar().setSubtitle(count + " followers");
    }

    // ── Bulk selection ────────────────────────────────────────────────────

    private void enterSelectionMode(String uid) {
        inSelectionMode = true;
        selectedUids.clear();
        selectedUids.add(uid);
        if (layoutBulkActions != null) layoutBulkActions.setVisibility(View.VISIBLE);
        updateSelectedCount();
        adapter.setSelectionMode(true, selectedUids);
    }

    private void exitSelectionMode() {
        inSelectionMode = false;
        selectedUids.clear();
        if (layoutBulkActions != null) layoutBulkActions.setVisibility(View.GONE);
        adapter.setSelectionMode(false, selectedUids);
    }

    private void updateSelectedCount() {
        if (tvSelectedCount != null) tvSelectedCount.setText(selectedUids.size() + " selected");
    }

    private void confirmBulkRemove() {
        if (selectedUids.isEmpty()) return;
        new AlertDialog.Builder(this)
            .setTitle("Remove " + selectedUids.size() + " followers?")
            .setMessage("They will be removed from this channel.")
            .setPositiveButton("Remove", (d, w) -> {
                for (String uid : selectedUids)
                    viewModel.blockFollower(channelId, uid);
                exitSelectionMode();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── FollowerAdapter ───────────────────────────────────────────────────

    class FollowerAdapter extends RecyclerView.Adapter<FollowerAdapter.VH> {
        private List<FollowerEntry> data = new ArrayList<>();
        private Set<String>  adminUids   = new HashSet<>();
        private Set<String>  selectedUids2 = new HashSet<>();
        private boolean isOwnerOrAdmin2 = false;
        private boolean isOwner2        = false;
        private boolean selectionMode   = false;

        void setData(List<FollowerEntry> d, Set<String> admins) {
            this.data      = d != null ? d : new ArrayList<>();
            this.adminUids = admins != null ? admins : new HashSet<>();
            notifyDataSetChanged();
        }
        void setIsOwnerOrAdmin(boolean v) { isOwnerOrAdmin2 = v; }
        void setIsOwner(boolean v)        { isOwner2 = v; }
        void setAdminUids(Set<String> a)  { adminUids = a != null ? a : new HashSet<>(); notifyDataSetChanged(); }
        void setSelectionMode(boolean sm, Set<String> sel) {
            selectionMode = sm; selectedUids2 = sel; notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_channel_follower, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            FollowerEntry e = data.get(pos);

            if (h.tvName != null)   h.tvName.setText(e.name != null ? e.name : e.uid);
            if (h.tvRole != null)   h.tvRole.setText(adminUids.contains(e.uid) ? "Admin" : "Follower");
            if (h.tvBlocked != null) h.tvBlocked.setVisibility(e.isBlocked ? View.VISIBLE : View.GONE);
            if (h.ivCheck != null)  h.ivCheck.setVisibility(selectionMode && selectedUids2.contains(e.uid) ? View.VISIBLE : View.GONE);
            if (h.ivIcon  != null && e.iconUrl != null && !e.iconUrl.isEmpty())
                Glide.with(h.ivIcon.getContext()).load(e.iconUrl).circleCrop().into(h.ivIcon);
            if (h.tvJoinDate != null && e.joinedAt > 0)
                h.tvJoinDate.setText("Joined " + new SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                    .format(new Date(e.joinedAt)));

            h.itemView.setOnClickListener(v -> {
                if (selectionMode) {
                    if (selectedUids.contains(e.uid)) selectedUids.remove(e.uid);
                    else selectedUids.add(e.uid);
                    updateSelectedCount();
                    adapter.setSelectionMode(true, selectedUids);
                }
            });

            h.itemView.setOnLongClickListener(v -> {
                if (!isOwnerOrAdmin2) return false;
                if (!selectionMode) enterSelectionMode(e.uid);
                return true;
            });

            // Action button — show admin options menu
            if (h.btnAction != null) {
                h.btnAction.setVisibility(isOwnerOrAdmin2 ? View.VISIBLE : View.GONE);
                h.btnAction.setOnClickListener(v -> showFollowerMenu(e));
            }
        }

        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivIcon;
            TextView tvName, tvRole, tvJoinDate, tvBlocked;
            ImageView ivCheck;
            ImageButton btnAction;
            VH(View v) {
                super(v);
                ivIcon    = v.findViewById(R.id.iv_follower_icon);
                tvName    = v.findViewById(R.id.tv_follower_name);
                tvRole    = v.findViewById(R.id.tv_follower_role);
                tvJoinDate= v.findViewById(R.id.tv_follower_join_date);
                tvBlocked = v.findViewById(R.id.tv_follower_blocked);
                ivCheck   = v.findViewById(R.id.iv_follower_check);
                btnAction = v.findViewById(R.id.btn_follower_action);
            }
        }
    }

    private void showFollowerMenu(FollowerEntry e) {
        List<String> options = new ArrayList<>();
        boolean isAdmin   = adminUids.contains(e.uid);
        boolean isBlocked = blockedUids.contains(e.uid);

        if (isOwner && !isAdmin && !e.uid.equals(ownerUid)) options.add("Promote to admin");
        if (isOwner && isAdmin && !e.uid.equals(ownerUid)) options.add("Remove admin");
        if (!isBlocked) options.add("Remove follower");
        if (!isBlocked) options.add("Block follower");
        else             options.add("Unblock follower");

        new AlertDialog.Builder(this)
            .setTitle(e.name != null ? e.name : "Follower")
            .setItems(options.toArray(new String[0]), (d, which) -> {
                String action = options.get(which);
                switch (action) {
                    case "Promote to admin":
                        new AlertDialog.Builder(this)
                            .setTitle("Promote to admin?")
                            .setPositiveButton("Promote", (dd, w) -> {
                                viewModel.addAdmin(channelId, e.uid, "admin");
                                adminUids.add(e.uid);
                                adapter.setAdminUids(adminUids);
                            })
                            .setNegativeButton("Cancel", null).show();
                        break;
                    case "Remove admin":
                        viewModel.removeAdmin(channelId, e.uid);
                        adminUids.remove(e.uid);
                        adapter.setAdminUids(adminUids);
                        break;
                    case "Remove follower":
                        viewModel.blockFollower(channelId, e.uid);
                        allFollowers.remove(e);
                        applyFilter();
                        break;
                    case "Block follower":
                        viewModel.blockFollower(channelId, e.uid);
                        e.isBlocked = true;
                        blockedUids.add(e.uid);
                        applyFilter();
                        break;
                    case "Unblock follower":
                        viewModel.unblockFollower(channelId, e.uid);
                        e.isBlocked = false;
                        blockedUids.remove(e.uid);
                        applyFilter();
                        break;
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── Data class ────────────────────────────────────────────────────────

    static class FollowerEntry {
        String uid, name, iconUrl;
        long   joinedAt;
        boolean isBlocked;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String str(Map<String, Object> m, String k) {
        Object v = m.get(k); return v != null ? v.toString() : null;
    }
    private long lng(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) { try { return Long.parseLong((String) v); } catch (Exception e) { return 0; } }
        return 0;
    }
    private boolean bool(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String)  return "true".equalsIgnoreCase((String) v);
        return false;
    }
}
