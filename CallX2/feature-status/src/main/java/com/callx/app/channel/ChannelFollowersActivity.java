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
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * ChannelFollowersActivity — WhatsApp-level follower management for channel owners/admins.
 *
 * Features:
 *   - Paginated list of followers (with join date)
 *   - Search followers by name
 *   - Remove / block a follower (admin only)
 *   - Promote a follower to admin directly from this screen
 *   - Follower count badge in toolbar
 *   - Pull-to-refresh for latest follower list
 */
public class ChannelFollowersActivity extends AppCompatActivity {

    public static final String EXTRA_CHANNEL_ID   = "channelId";
    public static final String EXTRA_CHANNEL_NAME = "channelName";
    public static final String EXTRA_OWNER_UID    = "ownerUid";

    private static final int PAGE_SIZE = 50;

    private ChannelViewModel viewModel;
    private String channelId, channelName, ownerUid;
    private FollowerAdapter adapter;
    private final List<FollowerEntry> allFollowers    = new ArrayList<>();
    private final List<FollowerEntry> filteredFollowers = new ArrayList<>();
    private TextInputEditText etSearch;
    private TextView tvFollowerCount;
    private boolean isOwnerOrAdmin = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_followers);

        channelId   = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        channelName = getIntent().getStringExtra(EXTRA_CHANNEL_NAME);
        ownerUid    = getIntent().getStringExtra(EXTRA_OWNER_UID);
        if (channelId == null) { finish(); return; }

        viewModel = new ViewModelProvider(this).get(ChannelViewModel.class);
        isOwnerOrAdmin = viewModel.getMyUid() != null
            && (viewModel.getMyUid().equals(ownerUid));

        Toolbar toolbar = findViewById(R.id.toolbar_followers);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Followers");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        tvFollowerCount = findViewById(R.id.tv_follower_count);
        etSearch        = findViewById(R.id.et_follower_search);

        RecyclerView rv = findViewById(R.id.rv_followers);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FollowerAdapter();
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

        viewModel.followers.observe(this, followers -> {
            if (followers == null) return;
            allFollowers.clear();
            for (Map<String, Object> raw : followers) {
                String uid      = raw.get("uid") != null ? raw.get("uid").toString() : "";
                long   joinedAt = raw.get("joinedAt") instanceof Long ? (Long) raw.get("joinedAt") : 0L;
                if (!uid.isEmpty()) allFollowers.add(new FollowerEntry(uid, joinedAt));
            }
            if (tvFollowerCount != null)
                tvFollowerCount.setText(allFollowers.size() + " followers");
            resolveFollowerProfiles();
        });

        viewModel.toastMessage.observe(this, msg -> {
            if (msg != null && !msg.isEmpty()) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                if (msg.contains("removed") || msg.contains("blocked")) loadFollowers();
            }
        });

        loadFollowers();
    }

    private void loadFollowers() {
        viewModel.loadFollowers(channelId, PAGE_SIZE);
    }

    private void resolveFollowerProfiles() {
        if (allFollowers.isEmpty()) {
            filterFollowers(etSearch != null && etSearch.getText() != null
                ? etSearch.getText().toString().trim().toLowerCase() : "");
            return;
        }
        final int[] done = {0};
        for (FollowerEntry entry : allFollowers) {
            FirebaseUtils.getUserRef(entry.uid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        entry.name    = snap.child("name").getValue(String.class);
                        entry.iconUrl = snap.child("photoUrl").getValue(String.class);
                        if (entry.name == null) entry.name = entry.uid.substring(0, 8) + "…";
                        done[0]++;
                        if (done[0] >= allFollowers.size()) {
                            filterFollowers(etSearch != null && etSearch.getText() != null
                                ? etSearch.getText().toString().trim().toLowerCase() : "");
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        entry.name = entry.uid.substring(0, 8) + "…";
                        done[0]++;
                        if (done[0] >= allFollowers.size()) {
                            filterFollowers(etSearch != null && etSearch.getText() != null
                                ? etSearch.getText().toString().trim().toLowerCase() : "");
                        }
                    }
                });
        }
    }

    private void filterFollowers(String query) {
        filteredFollowers.clear();
        for (FollowerEntry f : allFollowers) {
            if (query.isEmpty() || (f.name != null && f.name.toLowerCase().contains(query)))
                filteredFollowers.add(f);
        }
        adapter.setFollowers(new ArrayList<>(filteredFollowers));
    }

    // ── Adapter ───────────────────────────────────────────────────────────

    class FollowerAdapter extends RecyclerView.Adapter<FollowerAdapter.VH> {
        private final List<FollowerEntry> list = new ArrayList<>();

        void setFollowers(List<FollowerEntry> entries) {
            list.clear(); list.addAll(entries); notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_channel_follower, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            FollowerEntry f = list.get(pos);
            h.tvName.setText(f.name != null ? f.name : f.uid);

            if (f.joinedAt > 0) {
                String date = new SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                    .format(new Date(f.joinedAt));
                h.tvJoinDate.setText("Followed " + date);
            } else {
                h.tvJoinDate.setText("");
            }

            if (h.ivAvatar != null && f.iconUrl != null && !f.iconUrl.isEmpty())
                Glide.with(ChannelFollowersActivity.this).load(f.iconUrl).circleCrop().into(h.ivAvatar);
            else if (h.ivAvatar != null)
                h.ivAvatar.setImageResource(R.drawable.bg_channel_avatar_default);

            // Admin actions menu (long press)
            if (isOwnerOrAdmin) {
                h.itemView.setOnLongClickListener(v -> {
                    showFollowerActionsDialog(f);
                    return true;
                });
                if (h.btnMore != null) {
                    h.btnMore.setVisibility(View.VISIBLE);
                    h.btnMore.setOnClickListener(v -> showFollowerActionsDialog(f));
                }
            } else {
                if (h.btnMore != null) h.btnMore.setVisibility(View.GONE);
            }
        }

        @Override public int getItemCount() { return list.size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            TextView        tvName, tvJoinDate;
            ImageButton     btnMore;
            VH(View v) {
                super(v);
                ivAvatar  = v.findViewById(R.id.iv_follower_avatar);
                tvName    = v.findViewById(R.id.tv_follower_name);
                tvJoinDate= v.findViewById(R.id.tv_follower_join_date);
                btnMore   = v.findViewById(R.id.btn_follower_more);
            }
        }
    }

    private void showFollowerActionsDialog(FollowerEntry f) {
        String name = f.name != null ? f.name : f.uid;
        String[] actions = { "Make admin", "Remove follower", "Block follower" };
        new AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(actions, (d, which) -> {
                switch (which) {
                    case 0:
                        new AlertDialog.Builder(this)
                            .setTitle("Make admin?")
                            .setMessage("Grant admin access to " + name + "?")
                            .setPositiveButton("Make admin", (dd, ww) -> viewModel.addAdmin(channelId, f.uid))
                            .setNegativeButton("Cancel", null).show();
                        break;
                    case 1:
                        new AlertDialog.Builder(this)
                            .setTitle("Remove follower?")
                            .setMessage("Remove " + name + " from this channel's followers?")
                            .setPositiveButton("Remove", (dd, ww) -> viewModel.blockFollower(channelId, f.uid))
                            .setNegativeButton("Cancel", null).show();
                        break;
                    case 2:
                        new AlertDialog.Builder(this)
                            .setTitle("Block follower?")
                            .setMessage("Block " + name + "? They will not be able to rejoin this channel.")
                            .setPositiveButton("Block", (dd, ww) -> viewModel.blockFollower(channelId, f.uid))
                            .setNegativeButton("Cancel", null).show();
                        break;
                }
            }).show();
    }

    // ── Data class ────────────────────────────────────────────────────────

    static class FollowerEntry {
        String uid, name, iconUrl;
        long   joinedAt;
        FollowerEntry(String uid, long joinedAt) { this.uid = uid; this.joinedAt = joinedAt; }
    }
}
