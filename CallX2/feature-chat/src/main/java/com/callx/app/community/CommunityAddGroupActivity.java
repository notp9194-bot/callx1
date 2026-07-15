package com.callx.app.community;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.LiveData;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.GroupEntity;
import com.callx.app.repository.CommunityRepository;
import com.google.firebase.auth.FirebaseAuth;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * CommunityAddGroupActivity — pick from the current user's existing group
 * chats (Room "groups" table — same cache GroupsFragment reads) and link
 * them into the community via CommunityRepository#addGroupToCommunity.
 *
 * Availability rule: a group already linked to THIS community is excluded
 * from the list (re-derived each time from observeCommunityGroups()).
 */
public class CommunityAddGroupActivity extends AppCompatActivity {

    public static final String EXTRA_COMMUNITY_ID = "communityId";

    private String communityId, currentUid;
    private CommunityRepository repo;

    private RecyclerView rvGroups;
    private View emptyState;
    private android.widget.Button btnAddSelected;
    private PickAdapter adapter;

    private final Set<String> alreadyLinkedGroupIds = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_community_add_group);

        communityId = getIntent().getStringExtra(EXTRA_COMMUNITY_ID);
        currentUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        repo = CommunityRepository.getInstance(this);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("Add Group");
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        rvGroups = findViewById(R.id.rv_available_groups);
        emptyState = findViewById(R.id.empty_available_groups);
        btnAddSelected = findViewById(R.id.btn_add_selected);

        rvGroups.setLayoutManager(new LinearLayoutManager(this));
        rvGroups.setItemAnimator(null);
        adapter = new PickAdapter();
        rvGroups.setAdapter(adapter);

        btnAddSelected.setOnClickListener(v -> addSelectedGroups());

        if (communityId != null) {
            repo.observeCommunityGroups(communityId).observe(this, linkedGroups -> {
                alreadyLinkedGroupIds.clear();
                if (linkedGroups != null) {
                    for (GroupEntity g : linkedGroups) alreadyLinkedGroupIds.add(g.id);
                }
                loadMyGroups();
            });
        }
    }

    /**
     * Reads the user's own group cache directly from Room (same "groups"
     * table GroupsFragment observes) — there's no per-user "my groups"
     * query needed since GroupEntity already represents groups the user is in.
     */
    private void loadMyGroups() {
        new Thread(() -> {
            List<GroupEntity> all = AppDatabase.getInstance(this).groupDao().getAllGroupsSync();
            List<GroupEntity> available = new ArrayList<>();
            for (GroupEntity g : all) {
                if (!alreadyLinkedGroupIds.contains(g.id)) available.add(g);
            }
            runOnUiThread(() -> {
                adapter.submitList(available);
                boolean empty = available.isEmpty();
                rvGroups.setVisibility(empty ? View.GONE : View.VISIBLE);
                emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    private void addSelectedGroups() {
        List<String> selected = adapter.getSelectedIds();
        if (selected.isEmpty() || communityId == null || currentUid == null) {
            Toast.makeText(this, "Select at least one group", Toast.LENGTH_SHORT).show();
            return;
        }

        // v32: Community Access System — WhatsApp Communities-style choice:
        // "Everyone" auto-joins any community member who taps in; "Admins only"
        // requires an ask-to-join request approved by a community admin/owner.
        String[] options = { "Everyone in the community can join", "Only admins can add members" };
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Group access")
                .setItems(options, (d, which) -> {
                    String accessType = which == 1 ? "ADMIN_ONLY" : "OPEN";
                    linkSelectedGroups(selected, accessType);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void linkSelectedGroups(List<String> selected, String accessType) {
        btnAddSelected.setEnabled(false);
        final int[] remaining = { selected.size() };
        for (String groupId : selected) {
            repo.linkGroup(communityId, groupId, currentUid, accessType, (success, error) -> {
                remaining[0]--;
                if (remaining[0] <= 0) runOnUiThread(this::finish);
            });
        }
    }

    private static class PickAdapter extends RecyclerView.Adapter<PickAdapter.VH> {
        private final List<GroupEntity> items = new ArrayList<>();
        private final Set<String> selected = new HashSet<>();

        void submitList(List<GroupEntity> list) {
            items.clear();
            if (list != null) items.addAll(list);
            notifyDataSetChanged();
        }

        List<String> getSelectedIds() { return new ArrayList<>(selected); }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_community_add_group_checkbox, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            GroupEntity g = items.get(pos);
            h.tvName.setText(g.name != null ? g.name : "Group");
            h.tvDesc.setText(g.description != null ? g.description : "");
            if (g.iconUrl != null && !g.iconUrl.isEmpty()) {
                Glide.with(h.itemView.getContext()).load(g.iconUrl).circleCrop()
                        .placeholder(R.drawable.ic_group).into(h.ivIcon);
            } else {
                h.ivIcon.setImageResource(R.drawable.ic_group);
            }
            h.cbSelect.setChecked(selected.contains(g.id));
            h.itemView.setOnClickListener(v -> {
                if (selected.contains(g.id)) selected.remove(g.id); else selected.add(g.id);
                h.cbSelect.setChecked(selected.contains(g.id));
            });
        }

        @Override
        public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            CircleImageView ivIcon;
            TextView tvName, tvDesc;
            CheckBox cbSelect;
            VH(@NonNull View itemView) {
                super(itemView);
                ivIcon = itemView.findViewById(R.id.iv_group_icon);
                tvName = itemView.findViewById(R.id.tv_group_name);
                tvDesc = itemView.findViewById(R.id.tv_group_description);
                cbSelect = itemView.findViewById(R.id.cb_select);
            }
        }
    }
}
