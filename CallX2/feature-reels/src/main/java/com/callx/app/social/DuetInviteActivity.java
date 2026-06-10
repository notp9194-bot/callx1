package com.callx.app.social;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.models.User;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.PushNotify;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;

import java.util.*;

/**
 * DuetInviteActivity — Fix 11: Async / Invite-to-Duet
 *
 * Allows a reel creator to invite a specific user to duet their reel.
 *
 * Flow:
 *  1. Creator opens this from ReelMoreBottomSheet → "Invite to Duet"
 *  2. Search followers by username
 *  3. Tap user → send invite notification via PushNotify + store in Firebase
 *     duetInvites/{targetUid}/{inviteId} = { reelId, fromUid, fromName, videoUrl, timestamp }
 *  4. Target user sees notification; tapping opens DuetReelActivity for that reel
 *
 * Firebase node: duetInvites/{targetUid}/{inviteId}
 */
public class DuetInviteActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_ID    = "invite_reel_id";
    public static final String EXTRA_VIDEO_URL  = "invite_video_url";
    public static final String EXTRA_OWNER_NAME = "invite_owner_name";

    private EditText      etSearch;
    private RecyclerView  rvUsers;
    private ProgressBar   progressSearch;
    private TextView      tvNoResults;
    private ImageButton   btnBack;

    private String reelId, videoUrl, ownerName;
    private String myUid, myName, myPhoto;

    private final List<User>  results   = new ArrayList<>();
    private       UserAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_duet_invite);

        reelId    = getIntent().getStringExtra(EXTRA_REEL_ID);
        videoUrl  = getIntent().getStringExtra(EXTRA_VIDEO_URL);
        ownerName = getIntent().getStringExtra(EXTRA_OWNER_NAME);

        com.google.firebase.auth.FirebaseUser me =
            FirebaseAuth.getInstance().getCurrentUser();
        if (me == null) { finish(); return; }
        myUid = me.getUid();

        etSearch      = findViewById(R.id.et_invite_search);
        rvUsers       = findViewById(R.id.rv_invite_users);
        progressSearch= findViewById(R.id.progress_invite_search);
        tvNoResults   = findViewById(R.id.tv_invite_no_results);
        btnBack       = findViewById(R.id.btn_invite_back);

        btnBack.setOnClickListener(v -> finish());

        adapter = new UserAdapter(results, this::onUserTapped);
        rvUsers.setLayoutManager(new LinearLayoutManager(this));
        rvUsers.setAdapter(adapter);

        loadMyProfile();
        loadFollowers();

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                filterResults(s.toString().trim().toLowerCase());
            }
        });
    }

    private void loadMyProfile() {
        FirebaseUtils.db().getReference("users").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    myName  = snap.child("username").getValue(String.class);
                    myPhoto = snap.child("profileImage").getValue(String.class);
                    if (myName  == null) myName  = "Someone";
                    if (myPhoto == null) myPhoto = "";
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private final List<User> allFollowers = new ArrayList<>();

    private void loadFollowers() {
        if (progressSearch != null) progressSearch.setVisibility(View.VISIBLE);
        FirebaseUtils.db().getReference("followers").child(myUid)
            .limitToFirst(200)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;
                    allFollowers.clear();
                    List<String> uids = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) uids.add(ds.getKey());

                    if (uids.isEmpty()) {
                        if (progressSearch != null) progressSearch.setVisibility(View.GONE);
                        showNoResults(true);
                        return;
                    }

                    final int[] loaded = {0};
                    for (String uid : uids) {
                        FirebaseUtils.db().getReference("users").child(uid)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot u) {
                                    if (isFinishing() || isDestroyed()) return;
                                    User user = u.getValue(User.class);
                                    if (user != null) {
                                        if (user.uid == null) user.uid = u.getKey();
                                        allFollowers.add(user);
                                    }
                                    loaded[0]++;
                                    if (loaded[0] == uids.size()) {
                                        if (progressSearch != null)
                                            progressSearch.setVisibility(View.GONE);
                                        filterResults("");
                                    }
                                }
                                @Override public void onCancelled(@NonNull DatabaseError e) {
                                    loaded[0]++;
                                    if (loaded[0] == uids.size()) {
                                        if (progressSearch != null)
                                            progressSearch.setVisibility(View.GONE);
                                        filterResults("");
                                    }
                                }
                            });
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (progressSearch != null) progressSearch.setVisibility(View.GONE);
                    showNoResults(true);
                }
            });
    }

    private void filterResults(String query) {
        results.clear();
        for (User u : allFollowers) {
            String uname = u.username != null ? u.username.toLowerCase() : "";
            String name  = u.name     != null ? u.name.toLowerCase()     : "";
            if (query.isEmpty() || uname.contains(query) || name.contains(query)) {
                results.add(u);
            }
        }
        adapter.notifyDataSetChanged();
        showNoResults(results.isEmpty());
    }

    private void showNoResults(boolean show) {
        if (tvNoResults != null)
            tvNoResults.setVisibility(show ? View.VISIBLE : View.GONE);
        if (rvUsers != null)
            rvUsers.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    private void onUserTapped(User user) {
        if (reelId == null || user.uid == null) return;
        sendInvite(user);
    }

    private void sendInvite(User targetUser) {
        long now      = System.currentTimeMillis();
        String invKey = myUid + "_" + reelId;

        Map<String, Object> invite = new HashMap<>();
        invite.put("reelId",      reelId);
        invite.put("videoUrl",    videoUrl  != null ? videoUrl  : "");
        invite.put("fromUid",     myUid);
        invite.put("fromName",    myName    != null ? myName    : "Someone");
        invite.put("fromPhoto",   myPhoto   != null ? myPhoto   : "");
        invite.put("reelOwner",   ownerName != null ? ownerName : "");
        invite.put("timestamp",   now);
        invite.put("status",      "pending");

        FirebaseUtils.db()
            .getReference("duetInvites")
            .child(targetUser.uid)
            .child(invKey)
            .setValue(invite)
            .addOnSuccessListener(unused -> {
                PushNotify.notifyDuetInvite(
                    targetUser.uid, myUid,
                    myName != null ? myName : "Someone",
                    myPhoto != null ? myPhoto : "",
                    reelId,
                    videoUrl != null ? videoUrl : "");
                Toast.makeText(this,
                    "Invite sent to @" + (targetUser.username != null ? targetUser.username : "user"),
                    Toast.LENGTH_SHORT).show();
            })
            .addOnFailureListener(e ->
                Toast.makeText(this, "Failed to send invite", Toast.LENGTH_SHORT).show());
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private static class UserAdapter extends RecyclerView.Adapter<UserAdapter.VH> {

        interface OnClick { void onClick(User user); }

        private final List<User> items;
        private final OnClick    listener;

        UserAdapter(List<User> items, OnClick listener) {
            this.items    = items;
            this.listener = listener;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            android.view.View v = android.view.LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_invite_user, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            User u = items.get(pos);
            h.tvName.setText(u.username != null ? "@" + u.username : "Unknown");
            h.tvDisplayName.setText(u.name != null ? u.name : "");
            Glide.with(h.ivAvatar.getContext())
                .load(u.profileImage)
                .circleCrop()
                .placeholder(R.drawable.circle_avatar_bg)
                .into(h.ivAvatar);
            h.btnInvite.setOnClickListener(v -> listener.onClick(u));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            ImageView ivAvatar;
            TextView  tvName, tvDisplayName;
            TextView  btnInvite;
            VH(android.view.View v) {
                super(v);
                ivAvatar     = v.findViewById(R.id.iv_invite_avatar);
                tvName       = v.findViewById(R.id.tv_invite_username);
                tvDisplayName= v.findViewById(R.id.tv_invite_display_name);
                btnInvite    = v.findViewById(R.id.btn_send_invite);
            }
        }
    }
}
