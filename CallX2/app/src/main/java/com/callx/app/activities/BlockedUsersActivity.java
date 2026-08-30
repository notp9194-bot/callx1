package com.callx.app.activities;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.callx.app.R;
import com.callx.app.cache.MiscAvatarBinder;
import com.callx.app.models.User;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.firebase.database.*;
import java.util.*;

/**
 * BlockedUsersActivity — Unified blocked users list.
 *
 * Reads from: blocks/{myUid}/{blockedUid} = true  (unified path)
 * Allows:
 *   ✅ View count of blocked users (shown in subtitle)
 *   ✅ List all blocked users with avatar + name
 *   ✅ Unblock from this screen
 *   ✅ Empty state when no blocked users
 */
public class BlockedUsersActivity extends AppCompatActivity {

    private RecyclerView     rvBlocked;
    private ProgressBar      pbBlocked;
    private TextView         tvEmpty;
    private TextView         tvBlockCount;
    private BlockedAdapter   adapter;
    private final List<User> blockedUsers = new ArrayList<>();
    private String           myUid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_blocked_users);

        myUid = FirebaseUtils.getCurrentUid();
        if (myUid == null || myUid.isEmpty()) { finish(); return; }

        MaterialToolbar toolbar = findViewById(R.id.toolbar_blocked);
        if (toolbar != null) toolbar.setNavigationOnClickListener(v -> finish());

        rvBlocked    = findViewById(R.id.rv_blocked_users);
        pbBlocked    = findViewById(R.id.pb_blocked_users);
        tvEmpty      = findViewById(R.id.tv_blocked_empty);
        tvBlockCount = findViewById(R.id.tv_blocked_count);

        adapter = new BlockedAdapter();
        if (rvBlocked != null) {
            rvBlocked.setLayoutManager(new LinearLayoutManager(this));
            rvBlocked.setAdapter(adapter);
            // FIX (velocity-based prefetch): fast fling skips prefetch entirely,
            // slow/deliberate scroll warms several rows ahead — same thresholds
            // as ChatAvatarBinder/AvatarPrefetcher/FollowAvatarBinder.
            rvBlocked.addOnScrollListener(new RecyclerView.OnScrollListener() {
                private long lastTimeMs = 0L;
                @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                    if (lm == null) return;
                    int lastVisible = lm.findLastVisibleItemPosition();
                    if (lastVisible < 0) return;
                    long now = android.os.SystemClock.elapsedRealtime();
                    long dt = lastTimeMs == 0L ? 0L : (now - lastTimeMs);
                    float velocity = (dt > 0) ? Math.abs(dy) / (float) dt : 0f;
                    lastTimeMs = now;
                    MiscAvatarBinder.prefetch(BlockedUsersActivity.this, blockedAvatarSource(),
                        lastVisible + 1, velocity, MiscAvatarBinder.ROW_TIER);
                }
            });
        }

        loadBlockedUsers();
    }

    private void loadBlockedUsers() {
        if (pbBlocked != null) pbBlocked.setVisibility(View.VISIBLE);
        FirebaseUtils.getBlocksRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<String> uids = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren())
                        if (Boolean.TRUE.equals(ds.getValue(Boolean.class)) && ds.getKey() != null)
                            uids.add(ds.getKey());

                    if (pbBlocked != null) pbBlocked.setVisibility(View.GONE);
                    if (uids.isEmpty()) {
                        if (tvEmpty     != null) tvEmpty.setVisibility(View.VISIBLE);
                        if (tvBlockCount!= null) tvBlockCount.setText("0 blocked users");
                        return;
                    }
                    if (tvBlockCount != null) tvBlockCount.setText(uids.size() + " blocked user" + (uids.size() == 1 ? "" : "s"));

                    long[] pending = {uids.size()};
                    blockedUsers.clear();
                    for (String uid : uids) {
                        FirebaseUtils.getUserRef(uid)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot ds) {
                                    User u = ds.getValue(User.class);
                                    if (u == null) u = new User();
                                    u.uid = uid;
                                    if (u.name == null || u.name.isEmpty()) u.name = "User";
                                    blockedUsers.add(u);
                                    if (--pending[0] <= 0) {
                                        blockedUsers.sort((a, b) ->
                                            (a.name != null ? a.name : "").compareTo(b.name != null ? b.name : ""));
                                        adapter.notifyDataSetChanged();
                                        if (tvEmpty != null)
                                            tvEmpty.setVisibility(blockedUsers.isEmpty() ? View.VISIBLE : View.GONE);
                                    }
                                }
                                @Override public void onCancelled(@NonNull DatabaseError e) {
                                    if (--pending[0] <= 0) adapter.notifyDataSetChanged();
                                }
                            });
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (pbBlocked != null) pbBlocked.setVisibility(View.GONE);
                }
            });
    }

    private void confirmUnblock(User user) {
        com.callx.app.utils.AlertDialogStyler.showReusableConfirm(this,
                "blocked_user_unblock", com.callx.app.utils.AlertDialogStyler.DialogSize.DEFAULT,
                "Unblock " + user.name + "?", user.name + " will be able to message you and see your content again.",
                "Unblock", () -> {
                    FirebaseUtils.getBlocksRef(myUid).child(user.uid).removeValue()
                        .addOnSuccessListener(v -> {
                            blockedUsers.remove(user);
                            adapter.notifyDataSetChanged();
                            if (tvBlockCount != null)
                                tvBlockCount.setText(blockedUsers.size() + " blocked user" + (blockedUsers.size() == 1 ? "" : "s"));
                            if (tvEmpty != null)
                                tvEmpty.setVisibility(blockedUsers.isEmpty() ? View.VISIBLE : View.GONE);
                            Toast.makeText(this, user.name + " unblocked", Toast.LENGTH_SHORT).show();
                        });
                },
                null, null,
                "Cancel");
    }

    /** Read-only view over `blockedUsers` for {@link MiscAvatarBinder#prefetch}. */
    private MiscAvatarBinder.AvatarSource blockedAvatarSource() {
        return new MiscAvatarBinder.AvatarSource() {
            @Override public String photo(int index) { return blockedUsers.get(index).photoUrl; }
            @Override public long avatarVersion(int index) { return blockedUsers.get(index).avatarVersion; }
            @Override public int size() { return blockedUsers.size(); }
        };
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    class BlockedAdapter extends RecyclerView.Adapter<BlockedAdapter.VH> {

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_blocked_user, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            User u = blockedUsers.get(pos);
            h.tvName.setText(u.name);
            // FIX (deep avatar pipeline): was Glide.load().override(96,96) with
            // no shared tier, no L2/L3, no recycle-aware cancel — see MiscAvatarBinder.
            MiscAvatarBinder.bind(h.itemView.getContext(), h.ivAvatar,
                u.photoUrl, u.avatarVersion, MiscAvatarBinder.ROW_TIER, R.drawable.ic_person);
            h.btnUnblock.setOnClickListener(v -> confirmUnblock(u));
        }

        @Override public int getItemCount() { return blockedUsers.size(); }

        @Override
        public void onViewRecycled(@NonNull VH h) {
            // FIX (Lifecycle-aware cancel): stop an in-flight request for a
            // row that just scrolled off screen.
            MiscAvatarBinder.cancel(h.ivAvatar.getContext(), h.ivAvatar);
        }

        class VH extends RecyclerView.ViewHolder {
            ImageView ivAvatar;
            TextView  tvName;
            Button    btnUnblock;
            VH(View v) {
                super(v);
                ivAvatar  = v.findViewById(R.id.iv_blocked_avatar);
                tvName    = v.findViewById(R.id.tv_blocked_name);
                btnUnblock= v.findViewById(R.id.btn_unblock);
            }
        }
    }
}
