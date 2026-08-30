package com.callx.app.followers;
import com.callx.app.utils.AlertDialogStyler;

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
 * FollowingListActivity — jis user ki following dekhni hai unki list.
 *
 * Firebase path: reelFollows/{targetUid}/{followedUid} = true
 *
 * Features:
 *  ✅ Real-time following list with avatar + name
 *  ✅ Search filter (live by name)
 *  ✅ Unfollow button — sirf agar main hi woh user hoon (own profile)
 *  ✅ Tap row → opens their UserReelsActivity profile
 *  ✅ Empty state
 */
public class FollowingListActivity extends AppCompatActivity {

    public static final String EXTRA_UID      = "uid";
    public static final String EXTRA_NAME     = "name";
    public static final String EXTRA_IS_SELF  = "is_self";

    private RecyclerView rv;
    private EditText     etSearch;
    private ProgressBar  progressBar;
    private LinearLayout layoutEmpty;
    private ImageButton  btnBack;
    private TextView     tvTitle;

    private FollowingAdapter adapter;
    private final List<FollowersListActivity.UserItem> allItems      = new ArrayList<>();
    private final List<FollowersListActivity.UserItem> filteredItems = new ArrayList<>();

    private String  targetUid, targetName;
    private boolean isSelf;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_followers_list);  // reuse same layout

        targetUid  = getIntent().getStringExtra(EXTRA_UID);
        targetName = getIntent().getStringExtra(EXTRA_NAME);
        isSelf     = getIntent().getBooleanExtra(EXTRA_IS_SELF, false);
        if (targetUid == null) { finish(); return; }

        btnBack     = findViewById(R.id.btn_back);
        tvTitle     = findViewById(R.id.tv_title);
        etSearch    = findViewById(R.id.et_search);
        rv          = findViewById(R.id.rv_users);
        progressBar = findViewById(R.id.progress_bar);
        layoutEmpty = findViewById(R.id.layout_empty);

        if (btnBack != null) btnBack.setOnClickListener(v -> finish());
        if (tvTitle != null) tvTitle.setText(
            targetName != null ? targetName + "'s Following" : "Following");

        adapter = new FollowingAdapter(filteredItems);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
        attachVelocityPrefetch();

        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) { filterList(s.toString()); }
                @Override public void afterTextChanged(Editable s) {}
            });
        }

        loadFollowing();
    }

    private void loadFollowing() {
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        FirebaseUtils.getReelFollowsRef(targetUid)
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
                                    Long avatarVer = us.child("avatarVersion").getValue(Long.class);
                                    allItems.add(new FollowersListActivity.UserItem(uid,
                                        name  != null ? name  : "User",
                                        photo != null ? photo : "",
                                        bio   != null ? bio   : "",
                                        avatarVer != null ? avatarVer : 0L));
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
            for (FollowersListActivity.UserItem u : allItems)
                if (u.name.toLowerCase(Locale.getDefault()).contains(q)) filteredItems.add(u);
        }
        adapter.notifyDataSetChanged();
        if (layoutEmpty != null)
            layoutEmpty.setVisibility(filteredItems.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /**
     * FIX (velocity-based prefetch): same measurement ReelsFragment uses for
     * reels (px moved / ms since last callback) — fast fling skips prefetch,
     * slow scroll warms several rows ahead via FollowAvatarBinder.prefetch().
     */
    private void attachVelocityPrefetch() {
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            private long lastTimeMs = 0L;

            @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                long now = android.os.SystemClock.elapsedRealtime();
                long dt = lastTimeMs == 0L ? 0L : (now - lastTimeMs);
                float velocity = (dt > 0) ? Math.abs(dy) / (float) dt : 0f;
                lastTimeMs = now;

                LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (lm == null) return;
                int lastVisible = lm.findLastVisibleItemPosition();
                if (lastVisible < 0) return;

                FollowAvatarBinder.prefetch(FollowingListActivity.this, followAvatarSource(), lastVisible + 1, velocity);
            }
        });
    }

    private FollowAvatarBinder.AvatarSource followAvatarSource() {
        return new FollowAvatarBinder.AvatarSource() {
            @Override public String photo(int index) { return filteredItems.get(index).photo; }
            @Override public long avatarVersion(int index) { return filteredItems.get(index).avatarVersion; }
            @Override public int size() { return filteredItems.size(); }
        };
    }

    private void unfollowUser(FollowersListActivity.UserItem u, int pos) {
        AlertDialogStyler.showReusableConfirm(this, "unfollow_user",
            AlertDialogStyler.DialogSize.DEFAULT,
            "Unfollow",
            "Stop following " + u.name + "?",
            "Unfollow", () -> {
                FirebaseUtils.getReelFollowsRef(targetUid).child(u.uid).removeValue();
                FirebaseUtils.getReelFollowersRef(u.uid).child(targetUid).removeValue();
                allItems.remove(u);
                filteredItems.remove(u);
                adapter.notifyItemRemoved(pos);
                if (filteredItems.isEmpty() && layoutEmpty != null)
                    layoutEmpty.setVisibility(View.VISIBLE);
                // Update follower count on the followed user's node
                FirebaseDatabase.getInstance()
                    .getReference("users").child(u.uid).child("followersCount")
                    .runTransaction(new Transaction.Handler() {
                        @NonNull @Override public Transaction.Result doTransaction(@NonNull MutableData d) {
                            Integer c = d.getValue(Integer.class);
                            d.setValue(c != null && c > 0 ? c - 1 : 0);
                            return Transaction.success(d);
                        }
                        @Override public void onComplete(DatabaseError e, boolean b, DataSnapshot s) {}
                    });
            },
            null, null,
            "Cancel");
    }

    // ── Adapter ───────────────────────────────────────────────────────────

    class FollowingAdapter extends RecyclerView.Adapter<FollowingAdapter.VH> {
        final List<FollowersListActivity.UserItem> data;
        FollowingAdapter(List<FollowersListActivity.UserItem> data) { this.data = data; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_follow_user, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            FollowersListActivity.UserItem u = data.get(pos);
            h.tvName.setText(u.name);
            if (u.bio.isEmpty()) h.tvBio.setVisibility(View.GONE);
            else { h.tvBio.setText(u.bio); h.tvBio.setVisibility(View.VISIBLE); }

            if (!u.photo.isEmpty())
                FollowAvatarBinder.bind(FollowingListActivity.this, h.ivAvatar, u.photo, u.avatarVersion, R.drawable.ic_person);
            else h.ivAvatar.setImageResource(R.drawable.ic_person);

            // Unfollow button — only for own profile
            if (isSelf) {
                h.btnAction.setVisibility(View.VISIBLE);
                h.btnAction.setText("Following");
                android.util.TypedValue tv = new android.util.TypedValue();
                getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurfaceVariant, tv, true);
                h.btnAction.setBackgroundColor(tv.data);
                getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, tv, true);
                h.btnAction.setTextColor(tv.data);
                h.btnAction.setOnClickListener(v -> unfollowUser(u, h.getAdapterPosition()));
            } else {
                h.btnAction.setVisibility(View.GONE);
            }

            // Avatar tap → bottom sheet
            h.ivAvatar.setOnClickListener(v ->
                ReelUserProfileSheet.show(FollowingListActivity.this, u.uid, u.name, u.photo));

            h.itemView.setOnClickListener(v -> {
                android.content.Intent i = new android.content.Intent(
                    FollowingListActivity.this, UserReelsActivity.class);
                i.putExtra(UserReelsActivity.EXTRA_UID,   u.uid);
                i.putExtra(UserReelsActivity.EXTRA_NAME,  u.name);
                i.putExtra(UserReelsActivity.EXTRA_PHOTO, u.photo);
                startActivity(i);
            });
        }

        @Override public int getItemCount() { return data.size(); }

        @Override public void onViewRecycled(@NonNull VH h) {
            // FIX (lifecycle-aware cancel): row went off screen — stop its
            // in-flight avatar request instead of letting it keep running
            // for a view the user can no longer see.
            FollowAvatarBinder.cancel(FollowingListActivity.this, h.ivAvatar);
        }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            TextView tvName, tvBio;
            Button   btnAction;
            VH(@NonNull View v) {
                super(v);
                ivAvatar  = v.findViewById(R.id.iv_avatar);
                tvName    = v.findViewById(R.id.tv_name);
                tvBio     = v.findViewById(R.id.tv_bio);
                btnAction = v.findViewById(R.id.btn_follow_action);
            }
        }
    }
}
