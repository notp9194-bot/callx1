package com.callx.app.followers;

import com.callx.app.profile.ReelUserProfileSheet;
import com.callx.app.profile.UserReelsActivity;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.*;

/**
 * FollowersListActivity — jis user ke followers dekhne hain unki list.
 *
 * Firebase path: reelFollowers/{targetUid}/{followerUid} = true
 *
 * Features:
 *  ✅ Real-time follower list with avatar + name
 *  ✅ Search filter bar (live filter by name)
 *  ✅ Follow-back button (agar main unhe follow nahi karta)
 *  ✅ Tap row → opens their UserReelsActivity profile
 *  ✅ Empty state
 */
public class FollowersListActivity extends AppCompatActivity {

    public static final String EXTRA_UID   = "uid";
    public static final String EXTRA_NAME  = "name";

    private RecyclerView rv;
    private EditText     etSearch;
    private ProgressBar  progressBar;
    private LinearLayout layoutEmpty;
    private ImageButton  btnBack;
    private TextView     tvTitle;

    private FollowersAdapter adapter;
    private final List<UserItem> allItems      = new ArrayList<>();
    private final List<UserItem> filteredItems = new ArrayList<>();
    private final Set<String>    myFollowing   = new HashSet<>();

    private String targetUid, targetName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_followers_list);

        targetUid  = getIntent().getStringExtra(EXTRA_UID);
        targetName = getIntent().getStringExtra(EXTRA_NAME);
        if (targetUid == null) { finish(); return; }

        btnBack    = findViewById(R.id.btn_back);
        tvTitle    = findViewById(R.id.tv_title);
        etSearch   = findViewById(R.id.et_search);
        rv         = findViewById(R.id.rv_users);
        progressBar = findViewById(R.id.progress_bar);
        layoutEmpty = findViewById(R.id.layout_empty);

        if (btnBack  != null) btnBack.setOnClickListener(v -> finish());
        if (tvTitle  != null) tvTitle.setText(
            targetName != null ? targetName + "'s Followers" : "Followers");

        adapter = new FollowersAdapter(filteredItems, true);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) { filterList(s.toString()); }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        loadMyFollowing();
        loadFollowers();
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

    private void loadFollowers() {
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        FirebaseUtils.getReelFollowersRef(targetUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    allItems.clear();
                    long total = snap.getChildrenCount();
                    if (total == 0) { finishLoad(); return; }
                    final long[] count = {0};
                    for (DataSnapshot s : snap.getChildren()) {
                        String uid = s.getKey();
                        if (uid == null) { count[0]++; if (count[0] >= total) finishLoad(); continue; }
                        FirebaseUtils.getUserRef(uid)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot us) {
                                    String name  = us.child("name").getValue(String.class);
                                    String photo = us.child("photoUrl").getValue(String.class);
                                    String thumb = us.child("thumbUrl").getValue(String.class);
                                    photo = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                                    String bio   = us.child("bio").getValue(String.class);
                                    allItems.add(new UserItem(uid,
                                        name  != null ? name  : "User",
                                        photo != null ? photo : "",
                                        bio   != null ? bio   : ""));
                                    count[0]++;
                                    if (count[0] >= total) finishLoad();
                                }
                                @Override public void onCancelled(@NonNull DatabaseError e) {
                                    count[0]++; if (count[0] >= total) finishLoad();
                                }
                            });
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { finishLoad(); }
            });
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

    private String safeMyUid() {
        try { return FirebaseUtils.getCurrentUid(); } catch (Exception e) { return null; }
    }

    // ── Adapter ───────────────────────────────────────────────────────────

    class FollowersAdapter extends RecyclerView.Adapter<FollowersAdapter.VH> {
        final List<UserItem> data;
        final boolean        showFollowBack;
        FollowersAdapter(List<UserItem> data, boolean showFollowBack) {
            this.data = data; this.showFollowBack = showFollowBack;
        }

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
                Glide.with(FollowersListActivity.this).load(u.photo)
                    .apply(RequestOptions.circleCropTransform())
                    .override(96, 96)
                    .placeholder(R.drawable.ic_person).into(h.ivAvatar);
            else h.ivAvatar.setImageResource(R.drawable.ic_person);

            String myUid = safeMyUid();
            if (showFollowBack && myUid != null && !myUid.equals(u.uid)) {
                h.btnFollowBack.setVisibility(View.VISIBLE);
                boolean amFollowing = myFollowing.contains(u.uid);
                h.btnFollowBack.setText(amFollowing ? "Following" : "Follow Back");
                h.btnFollowBack.setBackgroundColor(amFollowing ? 0xFF333333
                    : getResources().getColor(R.color.brand_primary, null));
                h.btnFollowBack.setOnClickListener(v -> toggleFollowBack(u, h, pos));
            } else {
                h.btnFollowBack.setVisibility(View.GONE);
            }

            // Avatar tap → bottom sheet (same as Calls tab)
            h.ivAvatar.setOnClickListener(v ->
                ReelUserProfileSheet.show(FollowersListActivity.this, u.uid, u.name, u.photo));

            h.itemView.setOnClickListener(v -> {
                android.content.Intent i = new android.content.Intent(
                    FollowersListActivity.this, UserReelsActivity.class);
                i.putExtra(UserReelsActivity.EXTRA_UID,   u.uid);
                i.putExtra(UserReelsActivity.EXTRA_NAME,  u.name);
                i.putExtra(UserReelsActivity.EXTRA_PHOTO, u.photo);
                startActivity(i);
            });
        }

        private void toggleFollowBack(UserItem u, VH h, int pos) {
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
            TextView tvName, tvBio;
            Button   btnFollowBack;
            VH(@NonNull View v) {
                super(v);
                ivAvatar    = v.findViewById(R.id.iv_avatar);
                tvName      = v.findViewById(R.id.tv_name);
                tvBio       = v.findViewById(R.id.tv_bio);
                btnFollowBack = v.findViewById(R.id.btn_follow_action);
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
