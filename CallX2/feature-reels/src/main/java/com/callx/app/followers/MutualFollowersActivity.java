package com.callx.app.followers;

import com.callx.app.profile.ReelUserProfileSheet;
import com.callx.app.profile.UserReelsActivity;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;

import java.util.*;

import de.hdodenhof.circleimageview.CircleImageView;

/**
 * MutualFollowersActivity — FollowersListActivity jaisi screen,
 * sirf mutual followers dikhati hai.
 *
 * Features:
 *  ✅ Avatar + Name + Bio list (same as FollowersListActivity)
 *  ✅ Search filter bar
 *  ✅ Follow Back button
 *  ✅ Tap row → opens UserReelsActivity profile
 *  ✅ Empty state
 */
public class MutualFollowersActivity extends AppCompatActivity {

    public static final String EXTRA_UIDS        = "mutual_uids";
    public static final String EXTRA_TARGET_NAME = "target_name";

    private RecyclerView  rv;
    private EditText      etSearch;
    private ProgressBar   progressBar;
    private LinearLayout  layoutEmpty;
    private ImageButton   btnBack;
    private TextView      tvTitle;

    private MutualAdapter          adapter;
    private final List<UserItem>   allItems      = new ArrayList<>();
    private final List<UserItem>   filteredItems = new ArrayList<>();
    private final Set<String>      myFollowing   = new HashSet<>();

    private ArrayList<String> uidList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mutual_followers);

        btnBack     = findViewById(R.id.btn_back);
        tvTitle     = findViewById(R.id.tv_title);
        etSearch    = findViewById(R.id.et_search);
        rv          = findViewById(R.id.rv_users);
        progressBar = findViewById(R.id.progress_bar);
        layoutEmpty = findViewById(R.id.layout_empty);

        String targetName = getIntent().getStringExtra(EXTRA_TARGET_NAME);
        if (tvTitle != null)
            tvTitle.setText(targetName != null && !targetName.isEmpty()
                    ? "Mutual Followers with " + targetName
                    : "Mutual Followers");

        ArrayList<String> extras = getIntent().getStringArrayListExtra(EXTRA_UIDS);
        if (extras != null) uidList.addAll(extras);

        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        adapter = new MutualAdapter(filteredItems);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) { filterList(s.toString()); }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        if (uidList.isEmpty()) {
            showEmpty();
        } else {
            loadMyFollowing();
            loadMutualUsers();
        }
    }

    private void loadMyFollowing() {
        String myUid = safeMyUid();
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

    private void loadMutualUsers() {
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        final int total = uidList.size();
        final int[] count = {0};

        for (String uid : uidList) {
            FirebaseUtils.getUserRef(uid)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot us) {
                            String name  = us.child("name").getValue(String.class);
                            String photo = us.child("photoUrl").getValue(String.class);
                            String bio   = us.child("bio").getValue(String.class);
                            String uid2  = us.getKey();
                            allItems.add(new UserItem(uid2,
                                    name  != null ? name  : "User",
                                    photo != null ? photo : "",
                                    bio   != null ? bio   : ""));
                            count[0]++;
                            if (count[0] >= total) finishLoad();
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {
                            count[0]++;
                            if (count[0] >= total) finishLoad();
                        }
                    });
        }
    }

    private void finishLoad() {
        if (isFinishing() || isDestroyed()) return;
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        allItems.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        filteredItems.clear();
        filteredItems.addAll(allItems);
        adapter.notifyDataSetChanged();
        if (layoutEmpty != null)
            layoutEmpty.setVisibility(filteredItems.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void filterList(String query) {
        filteredItems.clear();
        if (query.isEmpty()) { filteredItems.addAll(allItems); }
        else {
            String q = query.toLowerCase(Locale.getDefault());
            for (UserItem u : allItems)
                if (u.name.toLowerCase(Locale.getDefault()).contains(q)) filteredItems.add(u);
        }
        adapter.notifyDataSetChanged();
        if (layoutEmpty != null)
            layoutEmpty.setVisibility(filteredItems.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showEmpty() {
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        if (layoutEmpty != null) layoutEmpty.setVisibility(View.VISIBLE);
    }

    private String safeMyUid() {
        try { return FirebaseUtils.getCurrentUid(); } catch (Exception e) { return null; }
    }

    // ── Adapter ───────────────────────────────────────────────────────────

    class MutualAdapter extends RecyclerView.Adapter<MutualAdapter.VH> {
        final List<UserItem> data;
        MutualAdapter(List<UserItem> data) { this.data = data; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_follow_user, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            UserItem u = data.get(pos);
            h.tvName.setText(u.name);

            if (u.bio.isEmpty()) { h.tvBio.setVisibility(View.GONE); }
            else { h.tvBio.setText(u.bio); h.tvBio.setVisibility(View.VISIBLE); }

            if (!u.photo.isEmpty())
                Glide.with(MutualFollowersActivity.this).load(u.photo)
                        .apply(RequestOptions.circleCropTransform())
                        .override(96, 96)
                        .placeholder(R.drawable.ic_person).into(h.ivAvatar);
            else h.ivAvatar.setImageResource(R.drawable.ic_person);

            String myUid = safeMyUid();
            if (myUid != null && !myUid.equals(u.uid)) {
                h.btnFollowAction.setVisibility(View.VISIBLE);
                boolean amFollowing = myFollowing.contains(u.uid);
                h.btnFollowAction.setText(amFollowing ? "Following" : "Follow Back");
                h.btnFollowAction.setBackgroundColor(amFollowing ? 0xFF333333
                        : getResources().getColor(R.color.brand_primary, null));
                h.btnFollowAction.setOnClickListener(v -> toggleFollow(u, h, pos));
            } else {
                h.btnFollowAction.setVisibility(View.GONE);
            }

            // Avatar tap → bottom sheet
            h.ivAvatar.setOnClickListener(v ->
                ReelUserProfileSheet.show(MutualFollowersActivity.this, u.uid, u.name, u.photo));

            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(MutualFollowersActivity.this, UserReelsActivity.class);
                i.putExtra(UserReelsActivity.EXTRA_UID,   u.uid);
                i.putExtra(UserReelsActivity.EXTRA_NAME,  u.name);
                i.putExtra(UserReelsActivity.EXTRA_PHOTO, u.photo);
                startActivity(i);
            });
        }

        private void toggleFollow(UserItem u, VH h, int pos) {
            String myUid = safeMyUid(); if (myUid == null) return;
            if (myFollowing.contains(u.uid)) {
                myFollowing.remove(u.uid);
                FirebaseUtils.getReelFollowsRef(myUid).child(u.uid).removeValue();
                FirebaseUtils.getReelFollowersRef(u.uid).child(myUid).removeValue();
            } else {
                myFollowing.add(u.uid);
                FirebaseUtils.getReelFollowsRef(myUid).child(u.uid).setValue(true);
                FirebaseUtils.getReelFollowersRef(u.uid).child(myUid).setValue(true);
            }
            notifyItemChanged(pos);
        }

        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            TextView        tvName, tvBio;
            Button          btnFollowAction;
            VH(@NonNull View v) {
                super(v);
                ivAvatar      = v.findViewById(R.id.iv_avatar);
                tvName        = v.findViewById(R.id.tv_name);
                tvBio         = v.findViewById(R.id.tv_bio);
                btnFollowAction = v.findViewById(R.id.btn_follow_action);
            }
        }
    }

    // ── Data class ────────────────────────────────────────────────────────
    static class UserItem {
        String uid, name, photo, bio;
        UserItem(String uid, String name, String photo, String bio) {
            this.uid = uid; this.name = name; this.photo = photo; this.bio = bio;
        }
    }
}
