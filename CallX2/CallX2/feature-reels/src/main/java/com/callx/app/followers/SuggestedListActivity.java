package com.callx.app.followers;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.callx.app.reels.R;
import com.callx.app.profile.UserReelsActivity;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.*;

/**
 * SuggestedListActivity — Instagram-style "Suggested for you" full list screen.
 *
 * Shows tabs: Followers | Following | Suggested
 * Suggested tab loads users who follow targetUser but current user doesn't follow yet.
 *
 * Extras:
 *   EXTRA_UID   — target user uid
 *   EXTRA_NAME  — target user display name
 *   EXTRA_TAB   — initial tab index (0=Followers, 1=Following, 2=Suggested)
 */
public class SuggestedListActivity extends AppCompatActivity {

    public static final String EXTRA_UID  = "uid";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_TAB  = "tab";
    public static final int    TAB_SUGGESTED = 2;

    private ImageButton btnBack;
    private TextView    tvUsername;
    private com.google.android.material.tabs.TabLayout tabLayout;
    private RecyclerView rv;
    private ProgressBar  progressBar;
    private LinearLayout layoutEmpty;

    private SuggestedAdapter adapter;
    private final List<SuggestedUser> allItems      = new ArrayList<>();
    private final List<SuggestedUser> filteredItems = new ArrayList<>();
    private final Set<String>         myFollowing   = new HashSet<>();

    private String targetUid, targetName;
    private int    initialTab = TAB_SUGGESTED;

    // Follower / following counts for tab labels
    private long followerCount  = 0;
    private long followingCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_suggested_list);

        targetUid  = getIntent().getStringExtra(EXTRA_UID);
        targetName = getIntent().getStringExtra(EXTRA_NAME);
        initialTab = getIntent().getIntExtra(EXTRA_TAB, TAB_SUGGESTED);
        if (targetUid == null) { finish(); return; }

        btnBack    = findViewById(R.id.btn_back);
        tvUsername = findViewById(R.id.tv_username);
        tabLayout  = findViewById(R.id.tab_layout);
        rv         = findViewById(R.id.rv_users);
        progressBar = findViewById(R.id.progress_bar);
        layoutEmpty = findViewById(R.id.layout_empty);

        if (btnBack != null) btnBack.setOnClickListener(v -> finish());
        if (tvUsername != null) tvUsername.setText(targetName != null ? targetName : "");

        adapter = new SuggestedAdapter(filteredItems);
        if (rv != null) {
            rv.setLayoutManager(new LinearLayoutManager(this));
            rv.setAdapter(adapter);
        }

        setupTabs();
        loadCounts();
        loadMyFollowing();
    }

    private void setupTabs() {
        if (tabLayout == null) return;
        tabLayout.addTab(tabLayout.newTab().setText("Followers"));
        tabLayout.addTab(tabLayout.newTab().setText("Following"));
        tabLayout.addTab(tabLayout.newTab().setText("Suggested"));
        tabLayout.selectTab(tabLayout.getTabAt(initialTab));
        tabLayout.addOnTabSelectedListener(new com.google.android.material.tabs.TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(com.google.android.material.tabs.TabLayout.Tab tab) {
                onTabChanged(tab.getPosition());
            }
            @Override public void onTabUnselected(com.google.android.material.tabs.TabLayout.Tab tab) {}
            @Override public void onTabReselected(com.google.android.material.tabs.TabLayout.Tab tab) {}
        });
    }

    private void onTabChanged(int pos) {
        allItems.clear();
        filteredItems.clear();
        adapter.notifyDataSetChanged();
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        if (layoutEmpty  != null) layoutEmpty.setVisibility(View.GONE);
        if (pos == 0)         loadFollowers();
        else if (pos == 1)    loadFollowing();
        else                  loadSuggested();
    }

    private void loadCounts() {
        final String DB = "https://sathix-97a76-default-rtdb.asia-southeast1.firebasedatabase.app";
        com.google.firebase.database.FirebaseDatabase.getInstance(DB)
            .getReference("reelFollowers").child(targetUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    followerCount = s.getChildrenCount();
                    updateTabLabels();
                    // Also trigger initial tab load after counts
                    if (initialTab == 0) loadFollowers();
                    else if (initialTab == 1) loadFollowing();
                    else loadSuggested();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (initialTab == 0) loadFollowers();
                    else if (initialTab == 1) loadFollowing();
                    else loadSuggested();
                }
            });
        com.google.firebase.database.FirebaseDatabase.getInstance(DB)
            .getReference("reelFollows").child(targetUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    followingCount = s.getChildrenCount();
                    updateTabLabels();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void updateTabLabels() {
        if (tabLayout == null) return;
        runOnUiThread(() -> {
            com.google.android.material.tabs.TabLayout.Tab t0 = tabLayout.getTabAt(0);
            com.google.android.material.tabs.TabLayout.Tab t1 = tabLayout.getTabAt(1);
            if (t0 != null) t0.setText(followerCount + " Followers");
            if (t1 != null) t1.setText(followingCount + " Following");
        });
    }

    private void loadMyFollowing() {
        String myUid = myUid();
        if (myUid == null) return;
        FirebaseUtils.getReelFollowsRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot s : snap.getChildren()) myFollowing.add(s.getKey());
                    adapter.notifyDataSetChanged();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ── Load Followers ──────────────────────────────────────────────────
    private void loadFollowers() {
        FirebaseUtils.getReelFollowersRef(targetUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    long total = snap.getChildrenCount();
                    if (total == 0) { finishLoad(); return; }
                    final long[] c = {0};
                    for (DataSnapshot s : snap.getChildren()) {
                        String uid = s.getKey();
                        if (uid == null) { c[0]++; if (c[0] >= total) finishLoad(); continue; }
                        fetchUserAndAdd(uid, total, c);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { finishLoad(); }
            });
    }

    // ── Load Following ──────────────────────────────────────────────────
    private void loadFollowing() {
        FirebaseUtils.getReelFollowsRef(targetUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    long total = snap.getChildrenCount();
                    if (total == 0) { finishLoad(); return; }
                    final long[] c = {0};
                    for (DataSnapshot s : snap.getChildren()) {
                        String uid = s.getKey();
                        if (uid == null) { c[0]++; if (c[0] >= total) finishLoad(); continue; }
                        fetchUserAndAdd(uid, total, c);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { finishLoad(); }
            });
    }

    // ── Load Suggested (followers of target whom I don't follow) ────────
    private void loadSuggested() {
        String myUid = myUid();
        FirebaseUtils.getReelFollowsRef(targetUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<String> candidates = new ArrayList<>();
                    for (DataSnapshot s : snap.getChildren()) {
                        String uid = s.getKey();
                        if (uid == null) continue;
                        if (uid.equals(myUid)) continue;          // skip self
                        if (myFollowing.contains(uid)) continue;  // already following
                        candidates.add(uid);
                    }
                    if (candidates.isEmpty()) { finishLoad(); return; }
                    long total = candidates.size();
                    final long[] c = {0};
                    for (String uid : candidates) fetchUserAndAdd(uid, total, c);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { finishLoad(); }
            });
    }

    private void fetchUserAndAdd(String uid, long total, long[] c) {
        FirebaseUtils.getUserRef(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot us) {
                    if (!isFinishing() && !isDestroyed()) {
                        String name  = us.child("name").getValue(String.class);
                        String photo = us.child("photoUrl").getValue(String.class);
                        String thumb = us.child("thumbUrl").getValue(String.class);
                        photo = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                        allItems.add(new SuggestedUser(uid,
                            name  != null ? name  : "User",
                            photo != null ? photo : ""));
                    }
                    c[0]++; if (c[0] >= total) finishLoad();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    c[0]++; if (c[0] >= total) finishLoad();
                }
            });
    }

    private void finishLoad() {
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> {
            if (progressBar != null) progressBar.setVisibility(View.GONE);
            allItems.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            filteredItems.clear();
            filteredItems.addAll(allItems);
            adapter.notifyDataSetChanged();
            if (layoutEmpty != null)
                layoutEmpty.setVisibility(filteredItems.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    private String myUid() {
        try {
            com.google.firebase.auth.FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
            return u != null ? u.getUid() : null;
        } catch (Exception e) { return null; }
    }

    // ── Data class ─────────────────────────────────────────────────────
    static class SuggestedUser {
        final String uid, name, photo;
        boolean followed = false;
        SuggestedUser(String uid, String name, String photo) {
            this.uid = uid; this.name = name; this.photo = photo;
        }
    }

    // ── Adapter ────────────────────────────────────────────────────────
    private class SuggestedAdapter extends RecyclerView.Adapter<SuggestedAdapter.VH> {
        private final List<SuggestedUser> data;
        SuggestedAdapter(List<SuggestedUser> data) { this.data = data; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_suggested_user, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            SuggestedUser u = data.get(pos);
            h.tvName.setText(u.name);
            h.tvSubtitle.setText("Instagram Suggested");
            if (u.photo != null && !u.photo.isEmpty()) {
                Glide.with(SuggestedListActivity.this)
                    .load(u.photo).circleCrop()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.ic_person)
                    .into(h.ivAvatar);
            } else {
                h.ivAvatar.setImageResource(R.drawable.ic_person);
            }
            updateFollowBtn(h, u);
            h.btnFollow.setOnClickListener(v -> {
                int p = h.getAdapterPosition();
                if (p == RecyclerView.NO_ID) return;
                SuggestedUser su = data.get(p);
                toggleFollow(su, h);
            });
            h.btnDismiss.setOnClickListener(v -> {
                int p = h.getAdapterPosition();
                if (p < 0 || p >= data.size()) return;
                data.remove(p);
                notifyItemRemoved(p);
                if (layoutEmpty != null)
                    layoutEmpty.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
            });
            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(SuggestedListActivity.this, UserReelsActivity.class);
                i.putExtra(UserReelsActivity.EXTRA_UID,  u.uid);
                i.putExtra(UserReelsActivity.EXTRA_NAME, u.name);
                if (u.photo != null && !u.photo.isEmpty())
                    i.putExtra(UserReelsActivity.EXTRA_PHOTO, u.photo);
                startActivity(i);
            });
        }

        private void updateFollowBtn(VH h, SuggestedUser u) {
            h.btnFollow.setText(u.followed ? "Following" : "Follow");
            h.btnFollow.setSelected(u.followed);
        }

        private void toggleFollow(SuggestedUser u, VH h) {
            String myUid = myUid();
            if (myUid == null) return;
            u.followed = !u.followed;
            if (u.followed) {
                FirebaseUtils.getReelFollowsRef(myUid).child(u.uid).setValue(true);
                FirebaseUtils.getReelFollowersRef(u.uid).child(myUid).setValue(true);
                myFollowing.add(u.uid);
            } else {
                FirebaseUtils.getReelFollowsRef(myUid).child(u.uid).removeValue();
                FirebaseUtils.getReelFollowersRef(u.uid).child(myUid).removeValue();
                myFollowing.remove(u.uid);
            }
            updateFollowBtn(h, u);
        }

        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            TextView        tvName, tvSubtitle;
            Button          btnFollow;
            ImageButton     btnDismiss;
            VH(View v) {
                super(v);
                ivAvatar   = v.findViewById(R.id.iv_avatar);
                tvName     = v.findViewById(R.id.tv_name);
                tvSubtitle = v.findViewById(R.id.tv_subtitle);
                btnFollow  = v.findViewById(R.id.btn_follow);
                btnDismiss = v.findViewById(R.id.btn_dismiss);
            }
        }
    }
}
